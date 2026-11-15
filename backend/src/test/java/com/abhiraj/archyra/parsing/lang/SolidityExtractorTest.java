package com.abhiraj.archyra.parsing.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.Test;

import com.abhiraj.archyra.ir.FileIR;
import com.abhiraj.archyra.ir.MethodIR;
import com.abhiraj.archyra.ir.RelationshipType;
import com.abhiraj.archyra.ir.TypeIR;
import com.abhiraj.archyra.ir.TypeKind;

class SolidityExtractorTest {

	private static final FixtureAnalysis ANALYSIS = FixtureAnalysis.of("solidity");

	private static FileIR file(String path) {
		return ANALYSIS.repository().files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
	}

	@Test
	void extractsImportsWithNamesAndWildcards() {
		assertThat(file("contracts/Token.sol").imports()).extracting("module", "name", "wildcard").containsExactly(
				tuple("./IToken.sol", null, true), tuple("./lib/Math.sol", "Math", false),
				tuple("./IVault.sol", null, true), tuple("./Base.sol", null, true),
				tuple("@openzeppelin/contracts/access/Ownable.sol", null, true));
	}

	@Test
	void extractsContractsLibrariesInterfacesAndTheirMembers() {
		TypeIR token = file("contracts/Token.sol").types().getFirst();

		assertThat(token.kind()).isEqualTo(TypeKind.CLASS);
		assertThat(token.qualifiedName()).isEqualTo("contracts.Token.Token");
		assertThat(token.extendsTypes()).extracting("name").containsExactly("Ownable", "Base");
		assertThat(token.implementsTypes()).extracting("name").containsExactly("IToken");
		assertThat(token.fields()).extracting("name").contains("vault");
		assertThat(token.methods()).extracting(MethodIR::name).containsExactly("Token", "transfer", "_mint");
		assertThat(token.methods().getFirst().constructor()).isTrue();
		assertThat(token.nestedTypes()).extracting(TypeIR::kind).containsExactly(TypeKind.STRUCT);
		assertThat(file("contracts/lib/Math.sol").types().getFirst().kind()).isEqualTo(TypeKind.OBJECT);
		assertThat(file("contracts/IToken.sol").types().getFirst().kind()).isEqualTo(TypeKind.INTERFACE);
	}

	@Test
	void resolvesImportsToFilesAndPackagesToExternalModules() {
		String token = "file:contracts/Token.sol";
		assertThat(ANALYSIS.edge(token, RelationshipType.IMPORTS, "file:contracts/IToken.sol")).isTrue();
		assertThat(ANALYSIS.edge(token, RelationshipType.IMPORTS, "file:contracts/lib/Math.sol")).isTrue();
		assertThat(ANALYSIS.edge(token, RelationshipType.IMPORTS, "file:contracts/Base.sol")).isTrue();
		assertThat(ANALYSIS.edge(token, RelationshipType.IMPORTS,
				"external:module:@openzeppelin/contracts/access/Ownable.sol")).isTrue();
	}

	@Test
	void resolvesInheritanceIncludingExternalBaseContracts() {
		String token = "class:contracts.Token.Token";
		assertThat(ANALYSIS.edge(token, RelationshipType.IMPLEMENTS, "interface:contracts.IToken.IToken")).isTrue();
		assertThat(ANALYSIS.edge(token, RelationshipType.EXTENDS, "class:contracts.Base.Base")).isTrue();
		assertThat(ANALYSIS.edge(token, RelationshipType.EXTENDS, "external:class:Ownable")).isTrue();
	}

	@Test
	void resolvesCallsThroughStateVariablesLibrariesAndSuper() {
		String transfer = "method:contracts.Token.Token#transfer(address,uint256)";
		assertThat(ANALYSIS.edge(transfer, RelationshipType.CALLS, "method:contracts.IVault.IVault#deposit(uint256)")).isTrue();
		assertThat(ANALYSIS.edge(transfer, RelationshipType.CALLS, "method:contracts.lib.Math.Math#add(uint,uint)")).isTrue();
		assertThat(ANALYSIS.edge(transfer, RelationshipType.CALLS, "method:contracts.Token.Token#_mint(address,uint256)")).isTrue();
		assertThat(ANALYSIS.edge(transfer, RelationshipType.CALLS, "method:contracts.Base.Base#hello()")).isTrue();
		assertThat(ANALYSIS.edge(transfer, RelationshipType.REFERENCES, "class:contracts.Token.Token")).isTrue();
	}

	@Test
	void derivesDependencies() {
		assertThat(ANALYSIS.edge("class:contracts.Token.Token", RelationshipType.DEPENDS_ON,
				"interface:contracts.IVault.IVault")).isTrue();
		assertThat(ANALYSIS.edge("class:contracts.Token.Token", RelationshipType.DEPENDS_ON,
				"class:contracts.lib.Math.Math")).isTrue();
		assertThat(ANALYSIS.edge("module:contracts", RelationshipType.DEPENDS_ON, "module:contracts/lib")).isTrue();
	}
}
